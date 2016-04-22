/*
 *  Copyright (C) 2014  Alfons Wirtz  
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * Library.java
 *
 * Created on 21. Mai 2004, 08:09
 */

package specctra;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import freert.planar.PlaVector;
import freert.planar.PlaVectorInt;
import freert.planar.ShapePolygon;
import freert.planar.ShapeTileSimplex;
import library.LibPadstack;
import specctra.varie.DsnReadUtils;
import board.BrdLayer;

/**
 * Class for reading and writing library scopes from dsn-files.
 *
 * @author Alfons Wirtz
 */
public class DsnKeywordLibrary extends DsnKeywordScope
   {
   public DsnKeywordLibrary()
      {
      super("library");
      }

   public boolean read_scope(DsnReadScopeParameters p_par)
      {
      board.RoutingBoard board = p_par.board_handling.get_routing_board();
      board.library.padstacks = new library.LibPadstacks(p_par.board_handling.get_routing_board().layer_structure);
      Collection<DsnKeywordPackage> package_list = new LinkedList<DsnKeywordPackage>();
      Object next_token = null;
      for (;;)
         {
         Object prev_token = next_token;
         try
            {
            next_token = p_par.scanner.next_token();
            }
         catch (java.io.IOException e)
            {
            System.out.println("Library.read_scope: IO error scanning file");
            System.out.println(e);
            return false;
            }
         if (next_token == null)
            {
            System.out.println("Library.read_scope: unexpected end of file");
            return false;
            }
         if (next_token == CLOSED_BRACKET)
            {
            // end of scope
            break;
            }
         if (prev_token == OPEN_BRACKET)
            {
            if (next_token == DsnKeyword.PADSTACK)
               {
               if (!read_padstack_scope(p_par.scanner, p_par.layer_structure, p_par.coordinate_transform, board.library.padstacks))
                  {
                  return false;
                  }
               }
            else if (next_token == DsnKeyword.IMAGE)
               {
               DsnKeywordPackage curr_package = DsnKeywordPackage.read_scope(p_par.scanner, p_par.layer_structure);
               if (curr_package == null)
                  {
                  return false;
                  }
               package_list.add(curr_package);
               }
            else
               {
               skip_scope(p_par.scanner);
               }
            }
         }

      // Set the via padstacks.
      if (p_par.via_padstack_names != null)
         {
         library.LibPadstack[] via_padstacks = new library.LibPadstack[p_par.via_padstack_names.size()];
         Iterator<String> it = p_par.via_padstack_names.iterator();
         int found_padstack_count = 0;
         for (int i = 0; i < via_padstacks.length; ++i)
            {
            String curr_padstack_name = it.next();
            library.LibPadstack curr_padstack = board.library.padstacks.get(curr_padstack_name);
            if (curr_padstack != null)
               {
               via_padstacks[found_padstack_count] = curr_padstack;
               ++found_padstack_count;
               }
            else
               {
               System.out.print("Library.read_scope: via padstack with name ");
               System.out.print(curr_padstack_name);
               System.out.println(" not found");
               }
            }
         if (found_padstack_count != via_padstacks.length)
            {
            // Some via padstacks were not found in the padstacks scope of the dsn-file.
            LibPadstack[] corrected_padstacks = new LibPadstack[found_padstack_count];
            System.arraycopy(via_padstacks, 0, corrected_padstacks, 0, found_padstack_count);
            via_padstacks = corrected_padstacks;
            }
         board.library.set_via_padstacks(via_padstacks);
         }

      // Create the library packages on the board
      board.library.packages = new library.LibPackages(board.library.padstacks);
      Iterator<DsnKeywordPackage> it = package_list.iterator();
      while (it.hasNext())
         {
         DsnKeywordPackage curr_package = it.next();
         library.LibPackagePin[] pin_arr = new library.LibPackagePin[curr_package.pin_info_arr.length];
         for (int i = 0; i < pin_arr.length; ++i)
            {
            DsnKeywordPackage.PinInfo pin_info = curr_package.pin_info_arr[i];
            int rel_x = (int) Math.round(p_par.coordinate_transform.dsn_to_board(pin_info.rel_coor[0]));
            int rel_y = (int) Math.round(p_par.coordinate_transform.dsn_to_board(pin_info.rel_coor[1]));
            PlaVector rel_coor = new PlaVectorInt(rel_x, rel_y);
            library.LibPadstack board_padstack = board.library.padstacks.get(pin_info.padstack_name);
            if (board_padstack == null)
               {
               System.out.println("Library.read_scope: board padstack not found");
               return false;
               }
            pin_arr[i] = new library.LibPackagePin(pin_info.pin_name, board_padstack.pads_no, rel_coor, pin_info.rotation);
            }
         freert.planar.PlaShape[] outline_arr = new freert.planar.PlaShape[curr_package.outline.size()];

         Iterator<DsnShape> it3 = curr_package.outline.iterator();
         for (int i = 0; i < outline_arr.length; ++i)
            {
            DsnShape curr_shape = it3.next();
            if (curr_shape != null)
               {
               outline_arr[i] = curr_shape.transform_to_board_rel(p_par.coordinate_transform);
               }
            else
               {
               System.out.println("Library.read_scope: outline shape is null");
               }
            }
         generate_missing_keepout_names("keepout_", curr_package.keepouts);
         generate_missing_keepout_names("via_keepout_", curr_package.via_keepouts);
         generate_missing_keepout_names("place_keepout_", curr_package.place_keepouts);
         library.LibPackageKeepout[] keepout_arr = new library.LibPackageKeepout[curr_package.keepouts.size()];
         Iterator<DsnScopeArea> it2 = curr_package.keepouts.iterator();
         for (int i = 0; i < keepout_arr.length; ++i)
            {
            DsnScopeArea curr_keepout = it2.next();
            DsnLayer curr_layer = curr_keepout.shape_list.iterator().next().layer;
            freert.planar.PlaArea curr_area = DsnShape.transform_area_to_board_rel(curr_keepout.shape_list, p_par.coordinate_transform);
            keepout_arr[i] = new library.LibPackageKeepout(curr_keepout.area_name, curr_area, curr_layer.layer_no);
            }
         library.LibPackageKeepout[] via_keepout_arr = new library.LibPackageKeepout[curr_package.via_keepouts.size()];
         it2 = curr_package.via_keepouts.iterator();
         for (int i = 0; i < via_keepout_arr.length; ++i)
            {
            DsnScopeArea curr_keepout = it2.next();
            DsnLayer curr_layer = (curr_keepout.shape_list.iterator().next()).layer;
            freert.planar.PlaArea curr_area = DsnShape.transform_area_to_board_rel(curr_keepout.shape_list, p_par.coordinate_transform);
            via_keepout_arr[i] = new library.LibPackageKeepout(curr_keepout.area_name, curr_area, curr_layer.layer_no);
            }
         library.LibPackageKeepout[] place_keepout_arr = new library.LibPackageKeepout[curr_package.place_keepouts.size()];
         it2 = curr_package.place_keepouts.iterator();
         for (int i = 0; i < place_keepout_arr.length; ++i)
            {
            DsnScopeArea curr_keepout = it2.next();
            DsnLayer curr_layer = (curr_keepout.shape_list.iterator().next()).layer;
            freert.planar.PlaArea curr_area = DsnShape.transform_area_to_board_rel(curr_keepout.shape_list, p_par.coordinate_transform);
            place_keepout_arr[i] = new library.LibPackageKeepout(curr_keepout.area_name, curr_area, curr_layer.layer_no);
            }
         board.library.packages.add(curr_package.name, pin_arr, outline_arr, keepout_arr, via_keepout_arr, place_keepout_arr, curr_package.is_front);
         }
      return true;
      }

   public static void write_scope(DsnWriteScopeParameter p_par) throws java.io.IOException
      {
      p_par.file.start_scope();
      p_par.file.write("library");
      for (int i = 1; i <= p_par.board.library.packages.count(); ++i)
         {
         DsnKeywordPackage.write_scope(p_par, p_par.board.library.packages.get(i));
         }
      for (int i = 1; i <= p_par.board.library.padstacks.count(); ++i)
         {
         write_padstack_scope(p_par, p_par.board.library.padstacks.get(i));
         }
      p_par.file.end_scope();
      }

   public static void write_padstack_scope(DsnWriteScopeParameter p_par, library.LibPadstack p_padstack) throws java.io.IOException
      {
      // search the layer range of the padstack
      int first_layer_no = 0;
      while (first_layer_no < p_par.board.get_layer_count())
         {
         if (p_padstack.get_shape(first_layer_no) != null)
            {
            break;
            }
         ++first_layer_no;
         }
      int last_layer_no = p_par.board.get_layer_count() - 1;
      while (last_layer_no >= 0)
         {
         if (p_padstack.get_shape(last_layer_no) != null)
            {
            break;
            }
         --last_layer_no;
         }
      if (first_layer_no >= p_par.board.get_layer_count() || last_layer_no < 0)
         {
         System.out.println("Library.write_padstack_scope: padstack shape not found");
         return;
         }

      p_par.file.start_scope();
      p_par.file.write("padstack ");
      p_par.identifier_type.write(p_padstack.pads_name, p_par.file);
      for (int index = first_layer_no; index <= last_layer_no; ++index)
         {
         freert.planar.PlaShape curr_board_shape = p_padstack.get_shape(index);
         if (curr_board_shape == null)
            {
            continue;
            }
         BrdLayer board_layer = p_par.board.layer_structure.get(index);
         DsnLayer curr_layer = new DsnLayer(board_layer.name, index, board_layer.is_signal);
         DsnShape curr_shape = p_par.coordinate_transform.board_to_dsn_rel(curr_board_shape, curr_layer);
         p_par.file.start_scope();
         p_par.file.write("shape");
         curr_shape.write_scope(p_par.file, p_par.identifier_type);
         p_par.file.end_scope();
         }
      if (!p_padstack.attach_allowed)
         {
         p_par.file.new_line();
         p_par.file.write("(attach off)");
         }
      if (p_padstack.placed_absolute)
         {
         p_par.file.new_line();
         p_par.file.write("(absolute on)");
         }
      p_par.file.end_scope();
      }

   public static boolean read_padstack_scope(JflexScanner p_scanner, DsnLayerStructure p_layer_structure, DsnCoordinateTransform p_coordinate_transform, library.LibPadstacks p_board_padstacks)
      {
      String padstack_name = null;
      boolean p_attach_allowed = true;
      boolean placed_absolute = false;
      Collection<DsnShape> shape_list = new LinkedList<DsnShape>();
      try
         {
         Object next_token = p_scanner.next_token();
         if (next_token instanceof String)
            {
            padstack_name = (String) next_token;
            }
         else
            {
            System.out.println("Library.read_padstack_scope: unexpected padstack identifier");
            return false;
            }

         while (next_token != DsnKeyword.CLOSED_BRACKET)
            {
            Object prev_token = next_token;
            next_token = p_scanner.next_token();
            if (prev_token == DsnKeyword.OPEN_BRACKET)
               {
               if (next_token == DsnKeyword.SHAPE)
                  {
                  DsnShape curr_shape = DsnShape.read_scope(p_scanner, p_layer_structure);
                  if (curr_shape != null)
                     {
                     shape_list.add(curr_shape);
                     }
                  // overread the closing bracket and unknown scopes.
                  Object curr_next_token = p_scanner.next_token();
                  while (curr_next_token == DsnKeyword.OPEN_BRACKET)
                     {
                     DsnKeywordScope.skip_scope(p_scanner);
                     curr_next_token = p_scanner.next_token();
                     }
                  if (curr_next_token != DsnKeyword.CLOSED_BRACKET)
                     {
                     System.out.println("Library.read_padstack_scope: closing bracket expected");
                     return false;
                     }
                  }
               else if (next_token == DsnKeyword.ATTACH)
                  {
                  // freeroute will overlap a pad and a via if it is allowed...
                  p_attach_allowed = DsnReadUtils.read_on_off_scope(p_scanner);
                  // however, kicad should report the correct state, when requested
                  }
               else if (next_token == DsnKeyword.ABSOLUTE)
                  {
                  placed_absolute = DsnReadUtils.read_on_off_scope(p_scanner);
                  }
               else
                  {
                  DsnKeywordScope.skip_scope(p_scanner);
                  }
               }

            }
         }
      catch (java.io.IOException e)
         {
         System.out.println("Library.read_padstack_scope: IO error scanning file");
         System.out.println(e);
         return false;
         }
      if (p_board_padstacks.get(padstack_name) != null)
         {
         // Padstack exists already
         return true;
         }
      if (shape_list.isEmpty())
         {
         System.out.print("Library.read_padstack_scope: shape not found for padstack with name ");
         System.out.println(padstack_name);
         return true;
         }
      freert.planar.ShapeConvex[] padstack_shapes = new freert.planar.ShapeConvex[p_layer_structure.arr.length];
      Iterator<DsnShape> it = shape_list.iterator();
      while (it.hasNext())
         {
         DsnShape pad_shape = it.next();
         freert.planar.PlaShape curr_shape = pad_shape.transform_to_board_rel(p_coordinate_transform);
         freert.planar.ShapeConvex convex_shape;
         if (curr_shape instanceof freert.planar.ShapeConvex)
            {
            convex_shape = (freert.planar.ShapeConvex) curr_shape;
            }
         else
            {
            if (curr_shape instanceof ShapePolygon)
               {
               curr_shape = ((ShapePolygon) curr_shape).convex_hull();
               }
            freert.planar.ShapeTile[] convex_shapes = curr_shape.split_to_convex();
            if (convex_shapes.length != 1)
               {
               System.out.println("Library.read_padstack_scope: convex shape expected");
               }
            convex_shape = convex_shapes[0];
            if (convex_shape instanceof ShapeTileSimplex)
               {
               convex_shape = ((ShapeTileSimplex) convex_shape).simplify();
               }
            }
         freert.planar.ShapeConvex padstack_shape = convex_shape;
         if (padstack_shape != null)
            {
            if ( ! padstack_shape.dimension().is_area() )
               {
               System.out.print("Library.read_padstack_scope: shape is not an area ");
               // enllarge the shape a little bit, so that it is an area
               padstack_shape = padstack_shape.offset(1);
               if ( ! padstack_shape.dimension().is_area() )
                  {
                  padstack_shape = null;
                  }
               }
            }

         if (pad_shape.layer == DsnLayer.PCB || pad_shape.layer == DsnLayer.SIGNAL)
            {
            for (int i = 0; i < padstack_shapes.length; ++i)
               {
               padstack_shapes[i] = padstack_shape;
               }
            }
         else
            {
            int shape_layer = p_layer_structure.get_no(pad_shape.layer.name);
            if (shape_layer < 0 || shape_layer >= padstack_shapes.length)
               {
               System.out.println("Library.read_padstack_scope: layer number found");
               return false;
               }
            padstack_shapes[shape_layer] = padstack_shape;
            }
         }
      p_board_padstacks.add(padstack_name, padstack_shapes, p_attach_allowed, placed_absolute);
      return true;
      }

   private void generate_missing_keepout_names(String p_keepout_type, Collection<DsnScopeArea> p_keepout_list)
      {
      boolean all_names_existing = true;
      for (DsnScopeArea curr_keepout : p_keepout_list)
         {
         if (curr_keepout.area_name == null)
            {
            all_names_existing = false;
            break;
            }
         }
      if (all_names_existing)
         {
         return;
         }
      // generate names
      Integer curr_name_index = 1;
      for (DsnScopeArea curr_keepout : p_keepout_list)
         {
         curr_keepout.area_name = p_keepout_type + curr_name_index.toString();
         ++curr_name_index;
         }
      }
   }
